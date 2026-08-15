package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.distParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 绿色物流碳足迹追踪与碳税仿真执行器（T030，CH11-004）。
 * 模型：运输（按海运/铁路/公路/空运因子 × 装载率修正）、生产（电力因子 × 清洁能源比例）、
 * 仓储与下游环节加总碳排放；超配额部分按碳税计成本，输出碳减排边际成本曲线。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class CarbonExecutor implements ScenarioExecutor {

    private static final double[] MODE_SHARE = {0.35, 0.20, 0.35, 0.10};   // 海运/铁路/公路/空运量占比
    private static final double SUPPLIER_TONNAGE = 2000.0;                 // 每供应商年运量（吨）

    @Override
    public String engineKey() {
        return "carbon";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Map<String, Double> factors = distParam(params, "carbon_factors",
                Map.of("sea", new double[]{5, 1000}, "rail", new double[]{5, 1000},
                        "road", new double[]{5, 1000}, "air", new double[]{5, 1000}), errors);
        Double electricity = doubleParam(params, "electricity_factor", 300, 800, errors);
        Double taxPrice = doubleParam(params, "carbon_tax_price", 50, 200, errors);
        Double quota = doubleParam(params, "carbon_quota", 100, 5000, errors);
        Double green = doubleParam(params, "green_premium", 0, 0.15, errors);
        Double clean = doubleParam(params, "clean_energy_ratio", 0, 1, errors);
        Double loading = doubleParam(params, "loading_rate", 0.5, 1.0, errors);
        double[][] distances = matrixParam(params, "supplier_distance", 1, 10, 1, 10, 5000, errors);
        if (errors.isEmpty() && factors != null && electricity != null && taxPrice != null
                && quota != null && green != null && clean != null && loading != null && distances != null) {
            // 约束 quota_ok：总碳排放量不得超过碳配额
            double total = totalEmission(factors, electricity, clean, loading, distances);
            if (total > quota) {
                errors.add(String.format("quota_ok 约束不满足：总碳排放量 %.0f 吨超过碳配额 %.0f 吨，请提升装载率或清洁能源比例",
                        total, quota));
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
        @SuppressWarnings("unchecked")
        Map<String, Object> factors = (Map<String, Object>) params.get("carbon_factors");
        double electricity = ((Number) params.get("electricity_factor")).doubleValue();
        double taxPrice = ((Number) params.get("carbon_tax_price")).doubleValue();
        double quota = ((Number) params.get("carbon_quota")).doubleValue();
        double green = ((Number) params.get("green_premium")).doubleValue();
        double clean = ((Number) params.get("clean_energy_ratio")).doubleValue();
        double loading = ((Number) params.get("loading_rate")).doubleValue();
        @SuppressWarnings("unchecked")
        List<List<Number>> distRaw = (List<List<Number>>) params.get("supplier_distance");

        // 步骤 1：碳源盘点与运输碳排放
        double totalTonKm = 0;
        for (List<Number> row : distRaw) {
            totalTonKm += SUPPLIER_TONNAGE * row.get(0).doubleValue();
        }
        double[] factorsArr = {((Number) factors.get("sea")).doubleValue(), ((Number) factors.get("rail")).doubleValue(),
                ((Number) factors.get("road")).doubleValue(), ((Number) factors.get("air")).doubleValue()};
        double weightedFactor = 0;
        for (int i = 0; i < 4; i++) {
            weightedFactor += MODE_SHARE[i] * factorsArr[i];
        }
        double transportEmission = totalTonKm * weightedFactor / loading / 1e6;   // gCO2 → 吨
        ctx.step(String.format("运输碳排放：总运量 %.0f 吨·公里，加权因子 %.0f gCO2/吨公里 ÷ 装载率 %.0f%% → %.0f 吨CO2",
                totalTonKm, weightedFactor, loading * 100, transportEmission),
                Map.of("transport_emission", round2(transportEmission)));

        // 步骤 2：生产环节（电力）
        double productionEmission = 300_000 * electricity / 1e6 * (1 - clean);
        ctx.step(String.format("生产碳排放：30 万 kWh × %.0f gCO2/kWh × (1-清洁能源 %.0f%%) → %.0f 吨CO2",
                electricity, clean * 100, productionEmission),
                Map.of("production_emission", round2(productionEmission)));

        // 步骤 3：仓储与下游
        double warehouseEmission = 80 * (1 - clean * 0.5);
        double downstreamEmission = 40;
        double total = transportEmission + productionEmission + warehouseEmission + downstreamEmission;
        ctx.step(String.format("仓储 %.0f 吨 + 下游配送 %.0f 吨 → 合计 %.0f 吨CO2（Scope 1/2/3）",
                warehouseEmission, downstreamEmission, total),
                Map.of("total_emission", round2(total)));

        // 步骤 4：碳税与配额
        double excess = Math.max(0, total - quota);
        double carbonCost = excess * taxPrice;
        double baseCost = 5_000_000.0;
        double energySaving = clean * 300_000.0;
        double greenRevenue = green * 20_000_000.0;
        double totalCost = baseCost + carbonCost - energySaving - greenRevenue;
        ctx.step(String.format("配额 %.0f 吨：超排 %.0f 吨 × %.0f 元/吨 → 碳成本 %.0f 元；含碳总成本 %.0f 元",
                quota, excess, taxPrice, carbonCost, totalCost),
                Map.of("carbon_cost", round2(carbonCost), "total_cost_with_carbon", round2(totalCost)));

        // 步骤 5：碳减排边际成本曲线与结论
        double[] abateX = {10, 20, 30, 40, 50, 60};
        double[] marginal = {15, 40, 85, 160, 280, 420};
        List<Number> xList = new ArrayList<>();
        List<Number> yList = new ArrayList<>();
        for (int i = 0; i < abateX.length; i++) {
            xList.add(abateX[i]);
            yList.add(round2(marginal[i] * taxPrice / 100.0));
        }
        String advice = excess > 0
                ? String.format("当前超排 %.0f 吨，建议优先装载率提升（边际成本仅 %.0f 元/吨）", excess, marginal[0] * taxPrice / 100.0)
                : "碳排放低于配额，可将剩余配额通过碳交易变现或提高绿色溢价收入";
        ctx.step(String.format("减排曲线：装载率 %d 元/吨 → 公转铁 %d 元/吨 → 清洁能源 %d 元/吨 → 就近采购 %d 元/吨。结论：%s",
                (int) marginal[0], (int) marginal[1], (int) marginal[2], (int) marginal[3], advice),
                Map.of("excess_emission", round2(Math.max(0, excess)), "advice", advice));

        // 输出指标（FR-007）
        ctx.output("total_emission", "总碳排放量", "scalar", round2(total), "吨CO2");
        ctx.output("emission_share", "各环节碳排放占比", "gauge",
                List.of(Map.of("name", "运输", "value", round2(transportEmission / total * 100)),
                        Map.of("name", "生产", "value", round2(productionEmission / total * 100)),
                        Map.of("name", "仓储", "value", round2(warehouseEmission / total * 100)),
                        Map.of("name", "下游配送", "value", round2(downstreamEmission / total * 100))),
                "%");
        ctx.output("carbon_cost", "碳税/碳交易成本", "scalar", round2(carbonCost), "元");
        ctx.output("total_cost_with_carbon", "含碳总成本", "scalar", round2(totalCost), "元");
        Map<String, Object> curve = new LinkedHashMap<>();
        curve.put("x", xList);
        curve.put("series", List.of(Map.of("name", "边际减排成本", "data", yList)));
        ctx.output("abatement_curve", "碳减排边际成本曲线", "series", curve, "元/吨");
    }

    /** 与 run() 完全一致的总排放计算（validate 复算用）。 */
    private static double totalEmission(Map<String, Double> factors, double electricity,
                                        double clean, double loading, double[][] distances) {
        double totalTonKm = 0;
        for (double[] row : distances) {
            totalTonKm += SUPPLIER_TONNAGE * row[0];
        }
        double weightedFactor = MODE_SHARE[0] * factors.get("sea") + MODE_SHARE[1] * factors.get("rail")
                + MODE_SHARE[2] * factors.get("road") + MODE_SHARE[3] * factors.get("air");
        double transport = totalTonKm * weightedFactor / loading / 1e6;
        double production = 300_000 * electricity / 1e6 * (1 - clean);
        double warehouse = 80 * (1 - clean * 0.5);
        return transport + production + warehouse + 40;
    }
}
