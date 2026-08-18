package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.OutputValue;
import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import com.scmmaisc.engine.StepAggregator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 跨境物流综合方案设计执行器（T056，CH5-008，comprehensive）。
 * 组合模型：以 StepAggregator 依次运行 5.1 直邮(CrossBorder) / 5.2 多式联运(Multimodal) /
 * 5.3 报关(Customs) / 5.4 班列(Crer) / 5.5 风险(CbRisk) / 5.6 保税(BondedWarehouse) /
 * 5.7 海外仓(OverseasWarehouse) 七个子模型（种子派生，FR-008），聚合步骤（R-13）；
 * 父模型按 SKU×目的国矩阵为每个组合选择模式（轻小低价→直邮 / 爆款标品→海外仓 / 正面清单→保税仓），
 * 计算组合总成本、加权时效、模式占比与需求 ±30% 敏感性。子模型输出不回并入父输出（契约以 JSON 为准）。
 */
@Component
public class CbComprehensiveExecutor implements ScenarioExecutor {

    private static final double POSITIVE_LIST_SHARE = 0.30; // 正面清单品类占比（保税仓）
    private static final double DIRECT_UNIT_COST = 45.0;    // 直邮单均成本（元/单）
    private static final double DIRECT_DAYS = 15.0;         // 直邮时效（天）
    private static final double FX_RATE = 7.2;              // 元/美元

    @Override
    public String engineKey() {
        return "cb-comprehensive";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer sku = intParam(params, "sku_count", 50, 5000, errors);
        Integer countries = intParam(params, "country_count", 1, 4, errors);
        Double costRate = doubleParam(params, "target_cost_rate", 0.05, 0.5, errors);
        Double premium = doubleParam(params, "premium_ratio", 0, 1, errors);
        Double variance = doubleParam(params, "demand_variance", 0.1, 0.5, errors);
        if (errors.isEmpty() && sku != null && countries != null && costRate != null
                && premium != null && variance != null) {
            // 约束 cost_rate_ok：目标物流成本率上限 50%
            if (costRate > 0.5) {
                errors.add("cost_rate_ok 约束不满足：目标物流成本率需 ≤ 50%");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 9; // 1 需求矩阵 + 7 子模型 + 1 组合优化
    }

    /** 从子模型上下文按 key 取输出值。 */    private static Object findOutput(SimContext child, String key) {
        for (OutputValue o : child.outputs()) {
            if (o.key().equals(key)) {
                return o.value();
            }
        }
        return null;
    }

    /** 解析 series 输出为终点值（累计曲线的最后一点）。 */
    @SuppressWarnings("unchecked")
    private static double seriesLast(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return Double.NaN;
        }
        Object raw = m.get("series");
        if (!(raw instanceof List<?> seriesList) || seriesList.isEmpty()) {
            return Double.NaN;
        }
        Object first = seriesList.get(0);
        if (!(first instanceof Map<?, ?> sm)) {
            return Double.NaN;
        }
        Object dataRaw = sm.get("data");
        if (!(dataRaw instanceof List<?> data) || data.isEmpty()) {
            return Double.NaN;
        }
        Object last = data.get(data.size() - 1);
        return last instanceof Number num ? num.doubleValue() : Double.NaN;
    }

    /** 解析 compare 输出为首项数值。 */
    @SuppressWarnings("unchecked")
    private static double compareFirst(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return Double.NaN;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> m)) {
            return Double.NaN;
        }
        Object v = m.get("value");
        return v instanceof Number num ? num.doubleValue() : Double.NaN;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int sku = ((Number) params.get("sku_count")).intValue();
        int countries = ((Number) params.get("country_count")).intValue();
        double targetCostRate = ((Number) params.get("target_cost_rate")).doubleValue();
        double premiumRatio = ((Number) params.get("premium_ratio")).doubleValue();
        double variance = ((Number) params.get("demand_variance")).doubleValue();

        // 步骤 1：需求矩阵与模式策略
        double lightShare = Math.max(0.05, 1 - POSITIVE_LIST_SHARE - premiumRatio);
        double premiumShare = premiumRatio;
        double positiveShare = POSITIVE_LIST_SHARE;
        long orders = (long) sku * countries * 120;
        ctx.step(String.format("%d SKU × %d 目的国 = %,.0f 单/年；策略：轻小低价 %.0f%%→直邮，爆款标品 %.0f%%→海外仓，正面清单 %.0f%%→保税仓",
                        sku, countries, (double) orders, lightShare * 100, premiumShare * 100, positiveShare * 100),
                Map.of("sku_count", sku, "country_count", countries, "orders_per_year", orders,
                        "light_share", round2(lightShare), "premium_share", round2(premiumShare)));

        // 步骤 2-8：七个子模型（StepAggregator 种子派生，步骤聚合 + 输出合并）
        long seed = ctx.seed();
        SimContext crossBorder = runSub(ctx, new CrossBorderExecutor(), subCrossBorder(), seed, 1, "跨境直邮子模型（5.1）", "cross-border");
        SimContext multimodal = runSub(ctx, new MultimodalExecutor(), subMultimodal(), seed, 2, "多式联运子模型（5.2）", "multimodal");
        SimContext customs = runSub(ctx, new CustomsExecutor(), subCustoms(), seed, 3, "报关通关子模型（5.3）", "customs");
        SimContext crer = runSub(ctx, new CrerExecutor(), subCrer(), seed, 4, "中欧班列子模型（5.4）", "crer");
        SimContext cbRisk = runSub(ctx, new CbRiskExecutor(), subCbRisk(), seed, 5, "风险应对子模型（5.5）", "cb-risk");
        SimContext bonded = runSub(ctx, new BondedWarehouseExecutor(), subBonded(sku), seed, 6, "保税仓子模型（5.6）", "bonded-warehouse");
        SimContext overseas = runSub(ctx, new OverseasWarehouseExecutor(), subOverseas(sku), seed, 7, "海外仓子模型（5.7）", "overseas-warehouse");

        // 步骤 9：组合优化与敏感性（子模型输出回读）
        double bondedDays = seriesLast(findOutput(bonded, "delivery_time"));
        if (Double.isNaN(bondedDays) || bondedDays <= 0) {
            bondedDays = 3.0;
        }
        double bondedCost = findOutput(bonded, "unit_logistics_cost") instanceof Number n ? n.doubleValue() : 12.0;
        double overseasCost = compareFirst(findOutput(overseas, "unit_cost_compare")) * FX_RATE;
        if (Double.isNaN(overseasCost) || overseasCost <= 0) {
            overseasCost = 75.0;
        }
        double overseasDays = compareFirst(findOutput(overseas, "delivery_compare"));
        if (Double.isNaN(overseasDays) || overseasDays <= 0) {
            overseasDays = 3.0;
        }
        double weightedUnit = lightShare * DIRECT_UNIT_COST + premiumShare * overseasCost
                + positiveShare * bondedCost;
        double totalCostWan = orders * weightedUnit / 10000;
        double weightedDays = lightShare * DIRECT_DAYS + premiumShare * overseasDays
                + positiveShare * bondedDays;
        double costRate = weightedUnit / 200.0; // 单均货值 200 元
        List<Map<String, Object>> mix = List.of(
                Map.of("name", "直邮", "value", round2(lightShare * 100)),
                Map.of("name", "海外仓", "value", round2(premiumShare * 100)),
                Map.of("name", "保税仓", "value", round2(positiveShare * 100)));
        List<Map<String, Object>> sensitivity = List.of(
                Map.of("name", "需求-30%", "value", round2(totalCostWan * (1 - variance))),
                Map.of("name", "基准需求", "value", round2(totalCostWan)),
                Map.of("name", "需求+30%", "value", round2(totalCostWan * (1 + variance))));
        String bestMix = String.format("推荐组合：保税仓 %.0f%%（正面清单，单均 %.1f 元）+ 直邮 %.0f%%（轻小低价，%.1f 元）+ 海外仓 %.0f%%（爆款标品，%.1f 元）；加权单均 %.1f 元，综合成本率 %.1f%%（目标 %.0f%% %s）",
                positiveShare * 100, bondedCost, lightShare * 100, DIRECT_UNIT_COST,
                premiumShare * 100, overseasCost, weightedUnit, costRate * 100,
                targetCostRate * 100, costRate <= targetCostRate ? "达标" : "超目标");
        ctx.step(String.format("组合总成本 %.1f 万元/年，加权时效 %.1f 天，综合成本率 %.1f%%（%s）",
                        totalCostWan, weightedDays, costRate * 100,
                        costRate <= targetCostRate ? "达标" : "需优化模式配比"),
                Map.of("total_cost_wan", round2(totalCostWan), "weighted_delivery", round2(weightedDays),
                        "cost_rate", round2(costRate * 100), "target_cost_rate", round2(targetCostRate * 100),
                        "mode_mix", mix, "sensitivity", sensitivity));

        // 输出指标（FR-007）
        ctx.output("total_cost", "组合方案总成本", "scalar", round2(totalCostWan), "万元");
        ctx.output("weighted_delivery", "加权平均时效", "scalar", round2(weightedDays), "天");
        ctx.output("mode_mix", "各模式占比", "gauge", mix, "%");
        ctx.output("best_mix", "ROI最高的模式组合", "scalar", bestMix, null);
        ctx.output("sensitivity", "敏感性分析(需求变动±30%)", "compare", sensitivity, "万元");
    }

    /** 运行子模型并聚合步骤（子模型输出仅供父模型回读，不并入父输出）。 */
    private SimContext runSub(SimContext parent, ScenarioExecutor sub, Map<String, Object> subParams,
                              long baseSeed, int stage, String stageLabel, String modelKey) {
        SimContext child = StepAggregator.runSubModel(sub, subParams, baseSeed, stage);
        StepAggregator.aggregate(parent, stage, stageLabel, modelKey, child);
        return child;
    }

    private Map<String, Object> subCrossBorder() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("trade_channel", "cn_us");
        p.put("transport_mode", "air");
        p.put("export_clearance_time", Map.of("mean", 1.0, "sd", 0.2));
        p.put("import_clearance_time", Map.of("mean", 2.0, "sd", 0.5));
        p.put("inspection_probability", 0.03);
        p.put("tariff_rate", 0.10);
        p.put("goods_category", "cross_ecommerce");
        return p;
    }

    private Map<String, Object> subMultimodal() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cargo_tonnage", 100.0);
        p.put("od_pair", "chongqing_duisburg");
        p.put("mode_attrs", null);
        p.put("transfer_cost", 10000.0);
        p.put("transfer_time", 12.0);
        p.put("time_priority", 0.5);
        return p;
    }

    private Map<String, Object> subCustoms() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("goods_type", "electronics");
        p.put("hs_accuracy", 0.95);
        p.put("declaration_value", "compliance");
        p.put("aeo_certified", false);
        p.put("doc_completeness", 0.98);
        p.put("random_inspect_rate", 0.03);
        p.put("targeted_inspect_rate", 0.10);
        return p;
    }

    private Map<String, Object> subCrer() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("origin_city", "chongqing");
        p.put("exit_port", "alashankou");
        p.put("train_frequency", 3);
        p.put("loading_rate", 0.8);
        p.put("cargo_category", "electronics");
        p.put("eu_distribution_days", 2.0);
        p.put("balance_ratio", 0.5);
        return p;
    }

    private Map<String, Object> subCbRisk() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("fx_volatility", 0.10);
        p.put("political_risk_prob", 0.05);
        p.put("natural_risk_prob", 0.02);
        p.put("fx_hedge_ratio", 0.5);
        p.put("backup_options", 1);
        p.put("insurance_ratio", 0.8);
        return p;
    }

    private Map<String, Object> subBonded(int sku) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("goods_category", "beauty");
        p.put("sku_count", sku);
        p.put("sku_stock_qty", 2000);
        p.put("tax_rate", 0.15);
        p.put("tax_free_quota", 2000.0);
        p.put("slow_sell_rate", 0.15);
        p.put("storage_fee", 0.1);
        return p;
    }

    private Map<String, Object> subOverseas(int sku) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("target_country", "usa");
        p.put("sku_count", sku);
        p.put("warehouse_mode", "fba");
        p.put("head_haul_cost", 12.0);
        p.put("storage_fee", 1.5);
        p.put("local_delivery_fee", 5.0);
        p.put("return_rate", 0.15);
        p.put("return_cost", 2.0);
        p.put("ltsf_fee", 8.0);
        return p;
    }
}
